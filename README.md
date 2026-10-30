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
- **Frontend:** React 18 + Vite

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