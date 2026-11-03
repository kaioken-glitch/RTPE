import '../lab.css';
import { useAuth } from '../auth.js';
import AccountCard from './lab/AccountCard.jsx';
import Tester from './lab/Tester.jsx';
import CommunityLog from './lab/CommunityLog.jsx';
import Leaderboard from './lab/Leaderboard.jsx';

export default function SentinelLab() {
  const auth = useAuth();
  const me = auth.user?.name ?? null;

  return (
    <div className="app-body lab">
      <div className="lab-col">
        <AccountCard auth={auth} />
        <Tester user={auth.user} />
      </div>
      <div className="lab-col">
        <CommunityLog me={me} />
        <Leaderboard me={me} />
      </div>
    </div>
  );
}
