import { useState } from 'react';

export default function AccountCard({ auth }) {
  const { user, ready, error, busy, available, login, register, googleLogin, logout, clearError } = auth;
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');

  if (!ready) return <section className="payment-section lab-card"><h2>Account</h2><p className="hint">Checking session</p></section>;

  if (user) {
    const s = user.stats;
    return (
      <section className="payment-section lab-card" aria-labelledby="acct-h">
        <div className="lab-row">
          <h2 id="acct-h">{user.name}</h2>
          <button className="sx-chip" onClick={logout}>Sign out</button>
        </div>
        <p className="lab-points"><b>{s.points}</b> points{user.rank ? <span> · rank {user.rank}</span> : null}</p>
        <dl className="lab-facts">
          <div><dt>Tests</dt><dd>{s.tests}</dd></div>
          <div><dt>Attacks caught</dt><dd>{s.attacks}</dd></div>
          <div><dt>Cleared</dt><dd>{s.cleared}</dd></div>
          <div><dt>Clean</dt><dd>{s.clean}</dd></div>
          <div><dt>Signatures</dt><dd>{s.signatures}</dd></div>
          <div><dt>Found first</dt><dd>{s.firsts}</dd></div>
        </dl>
        <p className="hint">Points are earned once per distinct payload, plus a bonus for the first person to submit it. Repeats count as tests only.</p>
      </section>
    );
  }

  const submit = async (e) => {
    e.preventDefault();
    const ok = await (mode === 'login' ? login(email, pw) : register(email, pw));
    if (ok) { setPw(''); setEmail(''); }
  };
  const switchMode = (m) => { setMode(m); clearError(); };

  return (
    <section className="payment-section lab-card" aria-labelledby="acct-h">
      <h2 id="acct-h">Account</h2>
      <div className="seg" role="tablist">
        <button role="tab" aria-selected={mode === 'login'} className={mode === 'login' ? 'on' : ''} onClick={() => switchMode('login')}>Sign in</button>
        <button role="tab" aria-selected={mode === 'register'} className={mode === 'register' ? 'on' : ''} onClick={() => switchMode('register')}>Create account</button>
      </div>
      <form onSubmit={submit} className="lab-form">
        <div className="field">
          <label htmlFor="lab-email">Email</label>
          <input id="lab-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" maxLength={254} required />
        </div>
        <div className="field">
          <label htmlFor="lab-pass">Password</label>
          <input id="lab-pass" type="password" value={pw} onChange={(e) => setPw(e.target.value)} maxLength={128}
                 minLength={mode === 'register' ? 8 : undefined} required
                 autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
        </div>
        {mode === 'register' && <p className="hint">Your email provider verifies your account. A display name is generated from your email for the public activity log.</p>}
        <button className="save-button solid" type="submit" disabled={!available || busy || !email.trim() || !pw}>
          {busy ? 'Working' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
        <button className="sx-chip" type="button" onClick={googleLogin} disabled={!available || busy}>
          Continue with Google
        </button>
        {error && <p className="error" role="alert">{error}</p>}
      </form>
      {!available && <p className="error" role="status">Authentication is not configured. Set the Supabase frontend environment variables to enable sign-in.</p>}
      <p className="hint">You can test without an account as a guest, but guests earn no points.</p>
    </section>
  );
}
