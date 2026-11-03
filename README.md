# RTPE

A debouncing, self-healing transaction queue with a live cockpit UI.

Duplicate requests for the same transaction are absorbed into a buffer while the first request owns a time window. If the owner fails, the next buffered duplicate is re-validated and promoted. If it succeeds, every duplicate is discarded.

## How it works

- **Identity:** a transaction is keyed by `(id, label)`. Same key means same transaction.
- **Windows:** the first request opens a window and later matches are absorbed.
  - `PAYMENT`: 2.0s
  - `REFUND`: 3.0s
  - `INVENTORY_LOCK`: 1.5s
- **Success:** the active slot and all absorbed duplicates are evicted.
- **Failure:** the next valid duplicate is promoted and gets a fresh window. If none remain, the transaction ends as a terminal failure.
- **Atomicity:** every state change per key runs inside `ConcurrentHashMap.compute`, so no global lock is needed.

## Cockpit indicators

| Phase | Indicator | Meaning |
|---|---|---|
| Tracking | Green, pulsing | Window open, no duplicates |
| Concurrent | Red, flashing | A similar request just arrived |
| Absorbing | Orange, pulsing | Duplicates held in the buffer |
| Resolved | Solid green | All flags cleared |

A header bar shows the worst phase across all open windows.

## Stack

- **Backend:** Java 17+, no dependencies
- **Frontend:** React 18 + Vite (Node.js 22+)

## Run locally

```bash
# Backend (port 8080, override with RTPE_PORT)
cd backend
mkdir -p out && javac -d out src/main/java/rtpe/*.java
java -cp out rtpe.RtpeServer

# Cockpit (port 5173, override API with VITE_RTPE_URL)
cd frontend
npm install
npm run dev
```

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/state?since=<seq>` | Wallet, stats, live windows, new log events |
| POST | `/api/transactions?id=&label=&amount=&fail=` | Submit a transaction |
| POST | `/api/reset` | Reset the sandbox |


Creating a test api for intrested users 

Or with Maven (JDK 17+):

```bash
cd backend
mvn -q package
java -jar target/rtpe-backend.jar
```

## Sentinel lab

Open the cockpit and go to `#lab` (or use "Sentinel lab" in the header). The lab supports Supabase email/password accounts and Google sign-in, an attack test window, a live Sentinel terminal and a contributor leaderboard. The optional custom trigger is off by default and adds a local-only terminal event every random 3–5 tester polls; it does not send a request.

Set these frontend build variables to your Supabase project:

```text
VITE_SUPABASE_URL=https://<project-ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<publishable-or-anon-key>
```

Set `SUPABASE_URL` and `SUPABASE_ANON_KEY` to the same project URL and public anon/publishable key in the backend environment. The backend verifies Supabase access tokens with Supabase Auth and issues its own short-lived Sentinel session; do not put a service-role key or Google client secret in frontend variables. In Supabase, enable Email and Google under **Authentication → Providers** and add your Google OAuth client ID and secret there. Set `https://<project-ref>.supabase.co/auth/v1/callback` as the authorized redirect URI in Google Cloud, then add the deployed frontend URL to Supabase's allowed redirect URLs.

Runtime data is written next to where the backend starts: `users.tsv` (legacy local accounts), `supabase-users.tsv` (Supabase-to-leaderboard name mapping), `contrib.tsv` (contributions) and `poi.tsv` (blacklist). On hosts with an ephemeral disk these files are lost on restart; configure `USERS_FILE`, `SUPABASE_USERS_FILE`, `CONTRIB_FILE` and `POI_FILE` on a persistent disk, or move them to a database.
