# RTPE

## Deploy the backend

The repository-root `Dockerfile` builds and runs the Java API from `backend/`.
For a Docker-based web service, use the repository root as the build context.
The server listens on `RTPE_PORT` when set, then `PORT`, and defaults to `8080`.

## Deploy the frontend

The Vite frontend is in `frontend/` and should be deployed as a separate static
site. Set its project root to `frontend`, build with `npm ci && npm run build`,
publish `dist`, and set `VITE_RTPE_URL` to the deployed backend URL.
