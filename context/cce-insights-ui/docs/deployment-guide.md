# Deployment Guide

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Docker | 20.10+ |
| Docker Compose | 2.x |
| Node.js (dev only) | 24 (CI builds on Node 24; the Docker build stage pins `node:20-alpine`) |
| cce-insights-service | Running on `deploy-scripts_cce-net` network |

## Architecture

The committed `Caddyfile` is the **gateway-less / local** topology — Caddy proxies API calls
straight to the insights-service, with no authentication:

```
Browser → :3001 → Caddy (cce-insights-ui container)
                    ├── static assets (React SPA)
                    └── /v1/insights/* → proxy → cce-insights-service:8084
```

The UI container runs Caddy which:
1. Serves the built React SPA for all browser routes
2. Proxies `/v1/insights/*` API requests to the insights-service container
3. No gateway required — direct service-to-service communication on the Docker network

> **Authenticated (gateway) topology.** In the deployed environments the API is fronted by
> `gateway-service`, which validates a Keycloak-issued JWT on every `/v1/insights/**` call, and
> the browser authenticates against Keycloak (Authorization Code + PKCE) before the SPA loads.
> That deployment requires extra steps — Keycloak client + role + audience-mapper setup, the
> gateway route, `api_permissions` seeding, and building the image with auth **build args** (see
> Configuration below). The full procedure is documented in
> [`artifacts/insights-ui-auth-deployment.md`](../artifacts/insights-ui-auth-deployment.md), which
> is the canonical auth-deployment reference.

## Quick Deploy (Docker Compose)

```bash
# From the project root
docker compose up -d --build
```

This will:
- Build a multi-stage Docker image (node:20-alpine → caddy:2-alpine)
- Join the `deploy-scripts_cce-net` network where `cce-insights-service` is running
- Expose the UI on http://localhost:3001

## Verify

```bash
# Check container is running
docker ps | grep cce-insights-ui

# Check health
curl -s http://localhost:3001/ | head -5

# Verify API proxy is working
curl -s http://localhost:3001/v1/insights/events/summary | head -c 200
```

## Stop

```bash
docker compose down
```

## Rebuild After Code Changes

```bash
docker compose up -d --build --force-recreate
```

## Local Development (without Docker)

```bash
cp .env.example .env
# Leave VITE_API_BASE_URL empty so the Vite dev proxy handles /v1/insights/
npm install
npm run dev          # http://localhost:3001
```

In dev mode, Vite's dev server proxies `/v1/insights/*` to `localhost:8088` (configured in `vite.config.ts`).

## Configuration

### Environment Variables (build-time)

All config is **build-time**: Vite inlines `VITE_*` into the bundle. For the Docker image they
are passed as `--build-arg` values (see the `Dockerfile` ARG/ENV block), so the deployed bundle is
baked per environment. Set them in `.env` for `npm run build`, or as build args for `docker build`.

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE_URL` | _(empty)_ | API base URL. Empty = relative (Caddy proxy / same-origin ingress). Set to `http://localhost:8084` only when calling the service directly in dev |
| `VITE_AUTH_ENABLED` | `false` | Enable Keycloak OIDC login (Authorization Code + PKCE). When `false`, the auth code path is inert and the app runs unauthenticated |
| `VITE_KEYCLOAK_URL` | _(empty)_ | Keycloak base URL. When empty, derived from `window.location.origin + '/auth'` (UI and Keycloak share a domain). Set only to point at a remote/fixed Keycloak |
| `VITE_KEYCLOAK_REALM` | `cce` | Keycloak realm |
| `VITE_KEYCLOAK_CLIENT_ID` | `cce-insights-ui` | Public Keycloak client id |
| `VITE_AUTH_TOKEN` | _(empty)_ | Static Bearer token fallback for local testing without an interactive login |
| `VITE_ROUTER_BASE` | `/` (dev) | SPA router base path. The Docker image bakes `/insights` (served under that path by the ingress) |
| `VITE_POLLING_INTERVAL` | `60000` | Dashboard auto-refresh interval (ms) |
| `VITE_DEFAULT_DATE_RANGE_DAYS` | `90` | Default date range filter |

### Caddy Configuration

The `Caddyfile` uses `handle` blocks for proper directive ordering:
- Listens on port 3001
- `handle /v1/insights/*` — reverse proxies API calls to `cce-insights-service:8084`
- `handle` (default) — serves the React SPA from `/srv` with `try_files` fallback to `index.html`
- Gzip/zstd compression via `encode`
- Aggressive caching for `/assets/*` (Vite hashed filenames)
- Security headers (X-Frame-Options, X-Content-Type-Options, Referrer-Policy)

### Docker Network

The compose file connects to the external `deploy-scripts_cce-net` network. If your insights-service is on a different network, update `docker-compose.yml`:

```yaml
networks:
  cce-net:
    external: true
    name: your_network_name
```

And update the upstream hostname in `Caddyfile` if the container name differs:

```caddyfile
handle /v1/insights/* {
    reverse_proxy your-insights-container:8084
}
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| 502 Bad Gateway on API calls | Ensure `cce-insights-service` is running and on the same Docker network |
| Page loads but API errors | Check `docker logs cce-insights-ui` for Caddy proxy errors |
| Blank page | Verify `dist/` was built correctly — rebuild with `docker compose up --build` |
| Container exits immediately | Check `docker logs cce-insights-ui` — likely Caddyfile syntax error |
