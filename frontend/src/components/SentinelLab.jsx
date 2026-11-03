import { useCallback, useRef, useState } from 'react';
import '../lab.css';
import { useAuth } from '../auth.js';
import AccountCard from './lab/AccountCard.jsx';
import Tester from './lab/Tester.jsx';
import CommunityLog from './lab/CommunityLog.jsx';
import Leaderboard from './lab/Leaderboard.jsx';

export default function SentinelLab() {
  const auth = useAuth();
  const me = auth.user?.name ?? null;
  const [triggerEnabled, setTriggerEnabled] = useState(false);
  const [triggerEvents, setTriggerEvents] = useState([]);
  const triggerSequence = useRef(0);
  const recordTrigger = useCallback((pulses) => {
    const sequence = ++triggerSequence.current;
    setTriggerEvents((events) => [...events, {
      seq: `trigger-${sequence}`,
      ts: Date.now(),
      who: 'local',
      kind: 'TRIGGER',
      id: `UI-${sequence}`,
      text: `Custom trigger fired after ${pulses} pulses (UI only)`,
      local: true,
    }].slice(-100));
  }, []);

  return (
    <div className="app-body lab">
      <div className="lab-col">
        <AccountCard auth={auth} />
        <Tester
          user={auth.user}
          triggerEnabled={triggerEnabled}
          onTriggerEnabledChange={setTriggerEnabled}
          onCustomTrigger={recordTrigger}
        />
      </div>
      <div className="lab-col">
        <CommunityLog me={me} triggerEvents={triggerEvents} />
        <Leaderboard me={me} />
      </div>
    </div>
  );
}
