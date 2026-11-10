# Reference build (PRD §6, Goal 8). Images pinned in D-05.

FROM node:20.20.2-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
ARG VITE_API_BASE_URL=""
ARG VITE_KEYCLOAK_URL=""
# No default: a build without the realm fails (C-02).
ARG VITE_KEYCLOAK_REALM
ARG VITE_KEYCLOAK_CLIENT_ID="cce-admin-ui"
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM
ENV VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
RUN npm run build

# Runs only with --target verify (UI-AD-11): lint, tests, and no mock code in the bundle.
FROM build AS verify
RUN npm run lint && npm run test:ci
RUN ! grep -rqE 'mockServiceWorker|setupServer' dist

FROM caddy:2.11.9-alpine
COPY --from=build /app/dist /srv
COPY Caddyfile /etc/caddy/Caddyfile
EXPOSE 3002
CMD ["sh", "-c", "[ -n \"$GATEWAY_UPSTREAM\" ] || { echo 'GATEWAY_UPSTREAM is required' >&2; exit 1; }; exec caddy run --config /etc/caddy/Caddyfile"]
