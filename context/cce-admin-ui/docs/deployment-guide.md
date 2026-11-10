# Deployment Guide

Same pattern as `cce-insights-ui` — see its
[deployment-guide.md](https://github.com/openphc/cce-insights-ui/blob/demo-rw/docs/deployment-guide.md)
for the general rationale (why Caddy, why build-time env). This document covers only
what differs for `cce-admin-ui`.

## Container build

Multi-stage Dockerfile, identical shape to `cce-insights-ui`'s:

1. **Build stage** — `node:20-alpine`: `npm ci`, `npm run build` with `VITE_*` values
   passed as `--build-arg`s (see [env-var-injection](#env-var-injection) below).
2. **Runtime stage** — `caddy:2-alpine`: serves the built static assets and
   reverse-proxies API calls.

## Port

Runs on **3002** — `cce-insights-ui` uses 3001; both consoles can run side by side
behind the same gateway without a port clash.

## Caddyfile

Same two responsibilities as `cce-insights-ui`'s Caddyfile:

- `try_files … index.html` fallback for SPA client-side routing.
- Reverse-proxy `/admin/v1/*` to `gateway-service` (not directly to
  `cce-admin-service` — see
  [api-integration.md §3](api-integration.md#3-gateway-routing)), so the deployed app
  and its API share an origin and need no CORS configuration.

## Env var injection

Same constraint as `cce-insights-ui`: Vite inlines `VITE_*` values into the bundle at
build time, so each environment (dev/staging/prod) needs its own image build with that
environment's `--build-arg` values — see
[developer-setup.md](developer-setup.md#environment-variables) for the variable list.
There is no runtime config reload; changing `VITE_KEYCLOAK_REALM` or
`VITE_API_BASE_URL` for a deployed environment means rebuilding the image.

## CI/CD

Same conventions as `cce-insights-ui`: CI builds and tests on Node 24; the Docker build
stage pins `node:20-alpine` for reproducibility independent of the CI runner's Node
version.
